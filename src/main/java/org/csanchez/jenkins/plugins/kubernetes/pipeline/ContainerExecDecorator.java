/*
 * Copyright (C) 2015 Original Authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *         http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.csanchez.jenkins.plugins.kubernetes.pipeline;

import static org.csanchez.jenkins.plugins.kubernetes.pipeline.Constants.*;

import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.io.PrintStream;
import java.io.Serializable;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;
import java.util.logging.Logger;

import hudson.EnvVars;
import hudson.FilePath;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import io.fabric8.kubernetes.client.KubernetesClientTimeoutException;
import org.apache.commons.io.output.TeeOutputStream;

import com.google.common.io.NullOutputStream;

import hudson.Launcher;
import hudson.LauncherDecorator;
import hudson.Proc;
import hudson.model.Node;
import io.fabric8.kubernetes.api.model.ContainerStatus;
import io.fabric8.kubernetes.api.model.Pod;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.Execable;
import io.fabric8.kubernetes.client.dsl.ExecListener;
import io.fabric8.kubernetes.client.dsl.ExecWatch;
import okhttp3.Response;
import org.jenkinsci.plugins.workflow.steps.EnvironmentExpander;

/**
 * This decorator interacts directly with the Kubernetes exec API to run commands inside a container. It does not use
 * the Jenkins slave to execute commands.
 */
public class ContainerExecDecorator extends LauncherDecorator implements Serializable, Closeable {

    private static final long serialVersionUID = 4419929753433397655L;
    private static final long DEFAULT_CONTAINER_READY_TIMEOUT = 5;
    private static final String CONTAINER_READY_TIMEOUT_SYSTEM_PROPERTY = ContainerExecDecorator.class.getName() + ".containerReadyTimeout";
    private static final long CONTAINER_READY_TIMEOUT = containerReadyTimeout();
    private static final String COOKIE_VAR = "JENKINS_SERVER_COOKIE";
    private static final Logger LOGGER = Logger.getLogger(ContainerExecDecorator.class.getName());

    private final transient KubernetesClient client;

    @SuppressFBWarnings(value = "SE_TRANSIENT_FIELD_NOT_RESTORED", justification = "not needed on deserialization")
    private final transient List<Closeable> closables = new ArrayList<>();
    @SuppressFBWarnings(value = "SE_TRANSIENT_FIELD_NOT_RESTORED", justification = "not needed on deserialization")
    private final transient Map<Integer, ContainerExecProc> processes = new HashMap<Integer, ContainerExecProc>();

    private final String podName;
    private final String namespace;
    private final String containerName;
    private final EnvironmentExpander environmentExpander;

    private final FilePath ws;

    public ContainerExecDecorator(KubernetesClient client, String podName, String containerName, String namespace, EnvironmentExpander environmentExpander, FilePath ws) {
        this.client = client;
        this.podName = podName;
        this.namespace = namespace;
        this.containerName = containerName;
        this.environmentExpander = environmentExpander;
        this.ws = ws;
    }

    public ContainerExecDecorator(KubernetesClient client, String podName, String containerName, String namespace) {
        this(client, podName, containerName, namespace, null, null);
    }

    @Deprecated
    public ContainerExecDecorator(KubernetesClient client, String podName, String containerName, AtomicBoolean alive, CountDownLatch started, CountDownLatch finished, String namespace) {
        this(client, podName, containerName, namespace, null, null);
    }

    @Deprecated
    public ContainerExecDecorator(KubernetesClient client, String podName, String containerName, AtomicBoolean alive, CountDownLatch started, CountDownLatch finished) {
        this(client, podName, containerName, (String) null, null, null);
    }

    @Deprecated
    public ContainerExecDecorator(KubernetesClient client, String podName, String containerName, String path, AtomicBoolean alive, CountDownLatch started, CountDownLatch finished) {
        this(client, podName, containerName, (String) null, null, null);
    }

    @Override
    public Launcher decorate(final Launcher launcher, final Node node) {
        return new Launcher.DecoratedLauncher(launcher) {
            @Override
            public Proc launch(ProcStarter starter) throws IOException {
                boolean quiet = starter.quiet();
                FilePath pwd = starter.pwd();
                String[] commands = getCommands(starter);

                return doLaunch(quiet, pwd, commands);
            }

            private Proc doLaunch(boolean quiet, FilePath pwd, String... commands) throws IOException {
                //check if its the actual script or the ProcessLiveness check.
                int p = parsePid(commands);
                //if it is a liveness check, try to find the actual process to avoid doing multiple execs.
                if (p == 9999) {
                    return new Proc() {
                        @Override
                        public boolean isAlive() throws IOException, InterruptedException {
                            return false;
                        }

                        @Override
                        public void kill() throws IOException, InterruptedException {

                        }

                        @Override
                        public int join() throws IOException, InterruptedException {
                            return 1;
                        }

                        @Override
                        public InputStream getStdout() {
                            return null;
                        }

                        @Override
                        public InputStream getStderr() {
                            return null;
                        }

                        @Override
                        public OutputStream getStdin() {
                            return null;
                        }
                    };
                } else if (p > 0 && processes.containsKey(p)) {
                    LOGGER.log(Level.INFO, "Retrieved process from cache with pid:[ " + p +"].");
                    Proc proc = processes.get(p);
                    return new Proc() {

                        @Override
                        public boolean isAlive() throws IOException, InterruptedException {
                            return false;
                        }

                        @Override
                        public void kill() throws IOException, InterruptedException {
                        }

                        @Override
                        public int join() throws IOException, InterruptedException {
                            return proc.isAlive() ? 0 : -1;
                        }

                        @Override
                        public InputStream getStdout() {
                            return null;
                        }

                        @Override
                        public InputStream getStderr() {
                            return null;
                        }

                        @Override
                        public OutputStream getStdin() {
                            return null;
                        }
                    };
                }

                waitUntilContainerIsReady();

                final CountDownLatch started = new CountDownLatch(1);
                final CountDownLatch finished = new CountDownLatch(1);
                final AtomicBoolean alive = new AtomicBoolean(false);

                PrintStream printStream = launcher.getListener().getLogger();
                OutputStream stream = printStream;
                // Do not send this command to the output when in quiet mode
                if (quiet) {
                    stream = new NullOutputStream();
                    printStream = new PrintStream(stream, false, StandardCharsets.UTF_8.toString());
                }

                // we need to keep the last bytes in the stream to parse the exit code as it is printed there
                // so we use a buffer
                ExitCodeOutputStream exitCodeOutputStream = new ExitCodeOutputStream();
                // send container output to all 3 streams (pid, out, job).
                stream = new TeeOutputStream(exitCodeOutputStream, stream);

                String msg = "Executing shell script inside container [" + containerName + "] of pod [" + podName + "]";
                LOGGER.log(Level.FINEST, msg);
                printStream.println(msg);

                Execable<String, ExecWatch> execable = client.pods().inNamespace(namespace).withName(podName).inContainer(containerName)
                        .redirectingInput().writingOutput(stream).writingError(stream)
                        .usingListener(new ExecListener() {
                            @Override
                            public void onOpen(Response response) {
                                alive.set(true);
                                started.countDown();
                            }

                            @Override
                            public void onFailure(Throwable t, Response response) {
                                alive.set(false);
                                t.printStackTrace(launcher.getListener().getLogger());
                                started.countDown();
                                LOGGER.log(Level.FINEST, "onFailure : {0}", finished);
                                if (finished.getCount() == 0) {
                                    LOGGER.log(Level.WARNING,
                                            "onFailure called but latch already finished. This may be a bug in the kubernetes-plugin");
                                }
                                finished.countDown();
                            }

                            @Override
                            public void onClose(int i, String s) {
                                alive.set(false);
                                started.countDown();
                                LOGGER.log(Level.FINEST, "onClose : {0}", finished);
                                if (finished.getCount() == 0) {
                                    LOGGER.log(Level.WARNING,
                                            "onClose called but latch already finished. This indicates a bug in the kubernetes-plugin");
                                }
                                finished.countDown();
                            }
                        });

                ExecWatch watch;
                try {
                    watch = execable.exec("/bin/sh");
                } catch (KubernetesClientException e) {
                    if (e.getCause() instanceof InterruptedException) {
                        throw new IOException("JENKINS-40825: interrupted while starting websocket connection", e);
                    } else {
                        throw e;
                    }
                }

                try {
                    started.await();
                } catch (InterruptedException e) {
                    closeWatch(watch);
                    throw new IOException("JENKINS-40825: interrupted while waiting for websocket connection", e);
                }

                try {
                    if (pwd != null) {
                        // We need to get into the project workspace.
                        // The workspace is not known in advance, so we have to execute a cd command.
                        watch.getInput().write(
                                String.format("cd \"%s\"%s", pwd, NEWLINE).getBytes(StandardCharsets.UTF_8));
                    }

                    if (environmentExpander != null) {
                        EnvVars envVars = new EnvVars();
                        environmentExpander.expand(envVars);
                        for (Map.Entry<String, String> entry : envVars.entrySet()) {
                            watch.getInput().write(
                                    String.format("export %s=\"%s\"%s", entry.getKey(), entry.getValue(), NEWLINE).getBytes(StandardCharsets.UTF_8));
                        }
                    }

                    doExec(watch, printStream, commands);

                    int pid = pid(commands);
                    LOGGER.log(Level.INFO, "Created process inside pod: ["+podName+"], container: ["+containerName+"] with pid:["+pid+"]");
                    ContainerExecProc proc = new ContainerExecProc(pid, watch, alive, finished, exitCodeOutputStream::getExitCode);
                    processes.put(pid, proc);
                    closables.add(proc);
                    return proc;
                } catch (InterruptedException ie) {
                    throw new InterruptedIOException(ie.getMessage());
                } catch (Exception e) {
                    closeWatch(watch);
                    throw e;
                }
            }

            @Override
            public void kill(Map<String, String> modelEnvVars) throws IOException, InterruptedException {
                getListener().getLogger().println("Killing processes");

                String cookie = modelEnvVars.get(COOKIE_VAR);

                int exitCode = doLaunch(
                        true, null,
                        "sh", "-c", "kill \\`grep -l '" + COOKIE_VAR + "=" + cookie + "' /proc/*/environ | cut -d / -f 3 \\`"
                ).join();

                getListener().getLogger().println("kill finished with exit code " + exitCode);
            }

            private void setupEnvironmentVariable(EnvVars vars, ExecWatch watch) throws IOException {
                for (Map.Entry<String, String> entry : vars.entrySet()) {
                    watch.getInput().write(
                            String.format(
                                    "export %s='%s'%s",
                                    entry.getKey(),
                                    entry.getValue().replace("'", "'\\''"),
                                    NEWLINE
                            ).getBytes(StandardCharsets.UTF_8)
                    );
                }
            }


            private void waitUntilContainerIsReady() throws IOException {
                try {
                    Pod pod = client.pods().inNamespace(namespace).withName(podName)
                            .waitUntilReady(CONTAINER_READY_TIMEOUT, TimeUnit.MINUTES);

                    if (pod == null || pod.getStatus() == null || pod.getStatus().getContainerStatuses() == null) {
                        throw new IOException("Failed to execute shell script inside container " +
                                "[" + containerName + "] of pod [" + podName + "]." +
                                "Failed to get container status");
                    }

                    for (ContainerStatus info : pod.getStatus().getContainerStatuses()) {
                        if (info.getName().equals(containerName)) {
                            if (info.getReady()) {
                                return;
                            } else {
                                // container died in the meantime
                                throw new IOException("container [" + containerName + "] of pod [" + podName + "] is not ready, state is " + info.getState());
                            }
                        }
                    }
                    throw new IOException("container [" + containerName + "] does not exist in pod [" + podName + "]");
                } catch (InterruptedException | KubernetesClientTimeoutException e) {
                    throw new IOException("Failed to execute shell script inside container " +
                            "[" + containerName + "] of pod [" + podName + "]." +
                            " Timed out waiting for container to become ready!", e);
                }
            }
        };
    }

    @Override
    public void close() throws IOException {
        for (Closeable closable : closables) {
            try {
                closable.close();
            } catch (Exception e) {
                LOGGER.log(Level.FINE, "failed to close {0}");
            }
        }
    }

    private static void doExec(ExecWatch watch, PrintStream out, String... statements) {
        try {
            out.print("Executing command: ");
            StringBuilder sb = new StringBuilder();
            for (String stmt : statements) {
                String s = String.format("\"%s\" ", stmt);
                sb.append(s);
                out.print(s);
                watch.getInput().write(s.getBytes(StandardCharsets.UTF_8));
            }
            sb.append(NEWLINE);
            out.println();
            watch.getInput().write(NEWLINE.getBytes(StandardCharsets.UTF_8));

            // get the command exit code and print it padded so it is easier to parse in ContainerExecProc
            // We need to exit so that we know when the command has finished.
            sb.append(ExitCodeOutputStream.EXIT_COMMAND);
            out.print(ExitCodeOutputStream.EXIT_COMMAND);
            LOGGER.log(Level.FINEST, "Executing command: {0}", sb);
            watch.getInput().write(ExitCodeOutputStream.EXIT_COMMAND.getBytes(StandardCharsets.UTF_8));

            out.flush();
            watch.getInput().flush();
        } catch (IOException e) {
            e.printStackTrace(out);
            throw new RuntimeException(e);
        }
    }

    static int parsePid(String... commands) {
        if (commands.length == 4 && "ps".equals(commands[0]) && "-o".equals(commands[1]) && commands[2].equals("pid=")) {
            return Integer.parseInt(commands[3]);
        }


        if (commands.length == 4 && "ps".equals(commands[0]) && "-o".equals(commands[1]) && commands[2].startsWith("-pid")) {
            return Integer.parseInt(commands[3]);
        }
        return -1;
    }

    static String readPidFile(String... commands) {
        if (commands.length >= 4 && "nohup".equals(commands[0]) && "sh".equals(commands[1]) && commands[2].equals("-c") && commands[3].startsWith("echo \\$\\$ >")) {
            return commands[3].substring(13, commands[3].indexOf(";") - 1);
        }
        return null;
    }

    private synchronized int pid(String... commands) throws IOException, InterruptedException {
        int pid = -1;
        FilePath pidFile = ws.child(readPidFile(commands));
        for (int w = 0; w < 10 && !pidFile.exists(); w++) {
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                Thread.interrupted();
                break;
            }
        }
        if (pidFile.exists()) {
            try {
                pid = Integer.parseInt(pidFile.readToString().trim());
            } catch (NumberFormatException x) {
                throw new IOException("corrupted content in " + pidFile + ": " + x, x);
            }
        }
        return pid;
    }

    static String[] getCommands(Launcher.ProcStarter starter) {
        List<String> allCommands = new ArrayList<String>();

        // BourneShellScript.launchWithCookie escapes $ as $$, we convert it to \$
        for (String cmd : starter.cmds()) {
            allCommands.add(cmd.replaceAll("\\$\\$", "\\\\\\$"));
        }
        return allCommands.toArray(new String[allCommands.size()]);
    }

    private static Long containerReadyTimeout() {
        String timeout = System.getProperty(CONTAINER_READY_TIMEOUT_SYSTEM_PROPERTY, String.valueOf(DEFAULT_CONTAINER_READY_TIMEOUT));
        try {
            return Long.parseLong(timeout);
        } catch (NumberFormatException e) {
            return DEFAULT_CONTAINER_READY_TIMEOUT;
        }
    }

    private static void closeWatch(ExecWatch watch) {
        try {
            watch.close();
        } catch (Exception e) {
            LOGGER.log(Level.INFO, "failed to close watch", e);
        }
    }

    /**
     * Keeps the last bytes of the output stream to parse the exit code
     */
    static class ExitCodeOutputStream extends OutputStream {

        public static final String EXIT_COMMAND_TXT = "EXITCODE";
        public static final String EXIT_COMMAND = "printf \"" + EXIT_COMMAND_TXT + " %3d\" $?; " + EXIT + NEWLINE;

        private EvictingQueue<Integer> queue = EvictingQueue.create(20);

        public ExitCodeOutputStream() {
        }

        @Override
        public void write(int b) throws IOException {
            queue.add(b);
            byte[] bb = new byte[]{(byte) b};
            System.out.print(new String(bb, StandardCharsets.UTF_8));
        }

        public int getExitCode() {
            ByteBuffer b = ByteBuffer.allocate(queue.size());
            queue.stream().filter(Objects::nonNull).forEach((i) -> b.put((byte) i.intValue()));
            // output ends in a 3 digit padded exit code + newline (13 10)
            // as defined in ContainerExecDecorator#doExec
            // ie. 32 32 49 13 10 for exit code 1
            int i = 1;
            String s = new String(b.array(), StandardCharsets.UTF_8);
            if (s.indexOf(EXIT_COMMAND_TXT) < 0) {
                LOGGER.log(Level.WARNING, "Unable to find \"{0}\" in {1}", new Object[]{EXIT_COMMAND_TXT, s});
                return i;
            }
            // parse the exitcode int printed after EXITCODE
            int start = s.indexOf(EXIT_COMMAND_TXT) + EXIT_COMMAND_TXT.length();
            s = s.substring(start, start + 4).trim();
            try {
                i = Integer.parseInt(s);
            } catch (NumberFormatException e) {
                LOGGER.log(Level.WARNING, "Unable to parse exit code as integer: \"{0}\" {1} / {2}",
                        new Object[]{s, queue.toString(), Arrays.toString(b.array())});
            }
            return i;
        }
    }
}
